from glob import glob

from setuptools import setup

package_name = 'android_bridge'

setup(
    name=package_name,
    version='0.1.0',
    packages=[package_name],
    data_files=[
        ('share/ament_index/resource_index/packages', ['resource/' + package_name]),
        ('share/' + package_name, ['package.xml']),
        ('share/' + package_name + '/config', glob('config/*.yaml')),
        ('share/' + package_name + '/launch', glob('launch/*.launch.py')),
    ],
    install_requires=['setuptools'],
    zip_safe=True,
    maintainer='Lorenzo Oliveira',
    maintainer_email='lorenzo.oliveira@ee.ufcg.edu.br',
    description='ROS 2 bridge for the androsid Android sensor streamer.',
    license='Apache-2.0',
    entry_points={
        'console_scripts': [
            'mobile_sensors = android_bridge.mobile_sensors:main',
        ],
    },
)
